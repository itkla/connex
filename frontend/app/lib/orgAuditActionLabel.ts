/**
 * Turns a dotted backend audit action code into something a person can read, so the organization
 * audit log never shows raw codes. `docs/PRODUCT.md` §4 bans raw enum/code fallbacks in copy.
 */

/**
 * Resolves the `OrgAudit.action.*` message key for an action code, but only when the code lands on
 * a real sentence. Dotted codes share prefixes (`org.member.set` and `org.member.remove` both sit
 * under `org.member`), so a lookup that stops on an intermediate object would render the key path
 * itself — this returns null for those and for unknown codes alike.
 *
 * @param messages the active locale's message tree, as returned by `useMessages()`
 * @param action the backend audit action code
 * @returns the full message key, or null when no sentence exists for this code
 */
export function orgAuditActionKey(messages: unknown, action: string): string | null {
    const parts = action.split(".").filter(Boolean);
    if (parts.length === 0) return null;

    let node = descend(messages, ["OrgAudit", "action"]);
    node = descend(node, parts);
    return typeof node === "string" ? `action.${parts.join(".")}` : null;
}

/**
 * Last-resort label for an action code Connex has no sentence for yet: one readable phrase in
 * sentence case rather than the raw dotted code.
 *
 * @param action the backend audit action code
 * @returns the code's words, sentence-cased
 */
export function titleCaseAction(action: string): string {
    const words = action.split(/[._]/).filter(Boolean);
    if (words.length === 0) return action;
    const [first, ...rest] = words;
    return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(" ");
}

function descend(node: unknown, path: readonly string[]): unknown {
    let current = node;
    for (const key of path) {
        if (typeof current !== "object" || current === null || Array.isArray(current)) return undefined;
        current = (current as Record<string, unknown>)[key];
    }
    return current;
}
