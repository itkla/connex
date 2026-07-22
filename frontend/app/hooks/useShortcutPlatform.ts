'use client';

import { useSyncExternalStore } from 'react';

import { resolveShortcutPlatform, type ShortcutPlatform } from '@/app/lib/actions/shortcut';

function subscribeToShortcutPlatform(): () => void {
    return () => {};
}

function shortcutPlatformSnapshot(): ShortcutPlatform {
    return resolveShortcutPlatform(navigator.platform);
}

function shortcutPlatformServerSnapshot(): null {
    return null;
}

/** Resolves the current platform's shortcut-label family after client hydration. */
export function useShortcutPlatform(): ShortcutPlatform | null {
    return useSyncExternalStore(
        subscribeToShortcutPlatform,
        shortcutPlatformSnapshot,
        shortcutPlatformServerSnapshot,
    );
}
