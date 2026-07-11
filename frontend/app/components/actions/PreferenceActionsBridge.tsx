"use client";

import { useMemo } from "react";
import { useTheme } from "next-themes";
import { ComputerDesktopIcon, GlobeAltIcon, LanguageIcon, MoonIcon, SunIcon } from "@heroicons/react/24/outline";

import { useRegisterActions } from "@/app/hooks/useActions";
import type { AppAction } from "@/app/lib/actions/types";

/** Sets the locale cookie next-intl reads, then reloads so the server re-renders in the new language. */
function setLocale(locale: "en" | "ja"): void {
    document.cookie = `NEXT_LOCALE=${locale}; path=/`;
    window.location.reload();
}

/**
 * Registers the workspace-and-preference actions (theme and language) into the shared registry so they
 * are searchable and executable from the command palette and any other surface. It lives in a bridge
 * because these actions need client capabilities (`next-themes`, the locale cookie) that the pure
 * registry context does not carry. Renders nothing.
 */
export default function PreferenceActionsBridge(): null {
    const { setTheme } = useTheme();

    const actions = useMemo<AppAction[]>(
        () => [
            {
                id: "workspace.theme-light",
                group: "workspace",
                labelKey: "workspace.themeLight",
                icon: SunIcon,
                order: 40,
                keywordsKey: "keywords.workspace.theme",
                execute: () => setTheme("light"),
            },
            {
                id: "workspace.theme-dark",
                group: "workspace",
                labelKey: "workspace.themeDark",
                icon: MoonIcon,
                order: 41,
                keywordsKey: "keywords.workspace.theme",
                execute: () => setTheme("dark"),
            },
            {
                id: "workspace.theme-system",
                group: "workspace",
                labelKey: "workspace.themeSystem",
                icon: ComputerDesktopIcon,
                order: 42,
                keywordsKey: "keywords.workspace.theme",
                execute: () => setTheme("system"),
            },
            {
                id: "workspace.language-en",
                group: "workspace",
                labelKey: "workspace.languageEnglish",
                icon: LanguageIcon,
                order: 50,
                keywordsKey: "keywords.workspace.language",
                execute: () => setLocale("en"),
            },
            {
                id: "workspace.language-ja",
                group: "workspace",
                labelKey: "workspace.languageJapanese",
                icon: GlobeAltIcon,
                order: 51,
                keywordsKey: "keywords.workspace.language",
                execute: () => setLocale("ja"),
            },
        ],
        [setTheme],
    );

    useRegisterActions(actions);
    return null;
}
