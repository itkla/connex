import policy from "./password-policy.json";

export const MAX_NEW_PASSWORD_LENGTH = policy.maxLength;
const rules = policy.rules.map((rule) => ({ ...rule, expression: new RegExp(rule.pattern, "u") }));

/** Returns the shared requirement keys that a new password still needs to satisfy. */
export function missingPasswordRequirements(password: string): string[] {
    const missing: string[] = [];
    if (password.length < policy.minLength || password.length > policy.maxLength) {
        missing.push("length");
    }
    if (new TextEncoder().encode(password).length > policy.maxBytes) {
        missing.push("byteLength");
    }
    return [...missing, ...rules.filter((rule) => !rule.expression.test(password)).map((rule) => rule.key)];
}
