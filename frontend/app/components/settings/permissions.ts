export type PermissionItem = { permission: string; action: string; description: string };
export type PermissionGroup = { group: string; label: string; items: PermissionItem[] };

type PermissionTranslator = {
    (key: string): string;
    has(key: string): boolean;
};

/** Groups the server catalog using business labels; unknown permissions never expose raw codes. */
export function groupPermissions(catalog: string[], t: PermissionTranslator): PermissionGroup[] {
    const groups = new Map<string, PermissionGroup>();
    for (const permission of catalog) {
        const group = permission.split('_')[0];
        const labelKey = `permissionGroups.${group}`;
        const actionKey = `permissionLabels.${permission}.action`;
        const descriptionKey = `permissionLabels.${permission}.description`;
        const existing = groups.get(group);
        const item = {
            permission,
            action: t.has(actionKey) ? t(actionKey) : t('unknownPermission'),
            description: t.has(descriptionKey) ? t(descriptionKey) : t('unknownPermissionDescription'),
        };
        if (existing) existing.items.push(item);
        else groups.set(group, {
            group,
            label: t.has(labelKey) ? t(labelKey) : t('unknownPermissionGroup'),
            items: [item],
        });
    }
    return [...groups.values()];
}
