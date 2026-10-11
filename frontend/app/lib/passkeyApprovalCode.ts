/**
 * Typing help for passkey approval codes (#1534). The server reads a code by its symbols alone,
 * in upper case, with O as 0 and I or L as 1, so the input applies the same reading as the member
 * types: what they see grouped on screen is exactly what the server will compare.
 */

const GROUP_LENGTH = 4;

/** Symbols in a whole code, without the hyphens between its groups. */
export const PASSKEY_APPROVAL_CODE_LENGTH = 16;

/**
 * The symbols of typed or pasted input as the server reads them, at most one code's worth.
 *
 * @param raw the input as typed or pasted
 * @returns upper-case letters and digits, with O read as 0 and I or L as 1
 */
export function passkeyApprovalCodeSymbols(raw: string): string {
    return raw
        .toUpperCase()
        .replace(/O/g, "0")
        .replace(/[IL]/g, "1")
        .replace(/[^0-9A-Z]/g, "")
        .slice(0, PASSKEY_APPROVAL_CODE_LENGTH);
}

/**
 * Input formatted the way codes are handed out, in groups of four joined by hyphens, as far as the
 * member has typed.
 *
 * @param raw the input as typed or pasted
 * @returns the grouped code, such as `ABCD-EFGH-JK`
 */
export function formatPasskeyApprovalCode(raw: string): string {
    const symbols = passkeyApprovalCodeSymbols(raw);
    const groups: string[] = [];
    for (let index = 0; index < symbols.length; index += GROUP_LENGTH) {
        groups.push(symbols.slice(index, index + GROUP_LENGTH));
    }
    return groups.join("-");
}

/**
 * Whether the input holds a whole code yet. It says nothing about whether the code is valid, which
 * only the server can tell.
 *
 * @param raw the input as typed or pasted
 * @returns true once the input holds all sixteen symbols
 */
export function isCompletePasskeyApprovalCode(raw: string): boolean {
    return passkeyApprovalCodeSymbols(raw).length === PASSKEY_APPROVAL_CODE_LENGTH;
}
