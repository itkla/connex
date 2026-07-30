const VIEW_PREFIX = 'connex:view:';

/** Builds the user- and workspace-scoped key for a persisted desktop view preference. */
export function viewPreferenceStorageKey(
    storageKey: string,
    userId: number | null,
    workspaceId: number | null,
): string {
    return `${VIEW_PREFIX}${userId ?? 'anon'}:${workspaceId ?? 'none'}:${storageKey}`;
}

/** Resolves a desktop view preference, preferring an explicit initial value over scoped storage. */
export function resolveViewPreference<T>(
    initialValue: unknown,
    storedValue: unknown,
    fallback: T,
    isValue: (value: unknown) => value is T,
): T {
    if (isValue(initialValue)) return initialValue;
    if (isValue(storedValue)) return storedValue;
    return fallback;
}

/** Forces a list presentation on phones while leaving the desktop preference unchanged. */
export function effectiveListView<T extends string>(
    desktopPreference: T,
    isMobile: boolean,
): T | 'list' {
    return isMobile ? 'list' : desktopPreference;
}
