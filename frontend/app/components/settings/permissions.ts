export type PermissionItem = { permission: string; action: string };
export type PermissionGroup = { group: string; label: string; items: PermissionItem[] };
export type PermissionLabel = { group: string; action: string };

const OVERRIDES: Record<string, string> = {
    MANAGE: 'Manage',
    SETTINGS: 'Settings',
};

/** Title-case a single SCREAMING_CASE token, e.g. COMPANY -> Company. */
function humanizeToken(token: string): string {
    return token.charAt(0).toUpperCase() + token.slice(1).toLowerCase();
}

/** Humanize the action portion of a permission key, e.g. CREATE -> Create. */
function humanizeAction(action: string): string {
    return action
        .split('_')
        .map((part) => OVERRIDES[part] ?? humanizeToken(part))
        .join(' ');
}

/** Split COMPANY_CREATE into its entity group and humanized action. */
function split(permission: string): { group: string; label: string; action: string } {
    const idx = permission.indexOf('_');
    const groupKey = idx >= 0 ? permission.slice(0, idx) : permission;
    const actionKey = idx >= 0 ? permission.slice(idx + 1) : permission;
    return { group: groupKey, label: humanizeToken(groupKey), action: humanizeAction(actionKey) };
}

/**
 * Group a flat permission catalog by entity (the part before the first `_`)
 * into humanized sections, preserving catalog order and applying localized overrides.
 */
export function groupPermissions(
    catalog: string[],
    labels: Partial<Record<string, PermissionLabel>> = {},
): PermissionGroup[] {
    const order: string[] = [];
    const map = new Map<string, PermissionGroup>();
    for (const permission of catalog) {
        const parsed = split(permission);
        const localized = labels[permission];
        const { group } = parsed;
        const label = localized?.group ?? parsed.label;
        const action = localized?.action ?? parsed.action;
        if (!map.has(group)) {
            map.set(group, { group, label, items: [] });
            order.push(group);
        }
        map.get(group)!.items.push({ permission, action });
    }
    return order.map((g) => map.get(g)!);
}
