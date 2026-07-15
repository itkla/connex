"use client";

import { useEffect, useMemo, useRef } from "react";
import { useLocale } from "next-intl";
import { useRouter } from "next/navigation";
import { useTheme } from "next-themes";
import { ComputerDesktopIcon, GlobeAltIcon, LanguageIcon, MoonIcon, SunIcon } from "@heroicons/react/24/outline";

import { useRegisterActions } from "@/app/hooks/useActions";
import type { AppAction } from "@/app/lib/actions/types";
import { persistAuthenticatedLocale } from "@/app/lib/locale-preference";
import { setLocaleCookie } from "@/app/lib/utils";
import type { Locale } from "@/i18n/config";

type Props = {
    userLocale: Locale;
    cookieLocale: Locale | null;
};

/**
 * Registers the workspace-and-preference actions (theme and language) into the shared registry so they
 * are searchable and executable from the command palette and any other surface. It lives in a bridge
 * because these actions need client capabilities (`next-themes`, the locale cookie) that the pure
 * registry context does not carry. Renders nothing.
 */
export default function PreferenceActionsBridge({ userLocale, cookieLocale }: Props): null {
    const { setTheme } = useTheme();
    const locale = useLocale();
    const router = useRouter();
    const setThemeRef = useRef(setTheme);
    const initializedLocaleRef = useRef(false);
    useEffect(() => {
        setThemeRef.current = setTheme;
    }, [setTheme]);

    useEffect(() => {
        if (initializedLocaleRef.current || cookieLocale !== null || userLocale === locale) return;
        initializedLocaleRef.current = true;
        setLocaleCookie(userLocale);
        router.refresh();
    }, [cookieLocale, locale, router, userLocale]);

    const actions = useMemo<readonly AppAction[]>(
        () => [
            {
                id: "workspace.theme-light",
                group: "workspace",
                labelKey: "workspace.themeLight",
                icon: SunIcon,
                order: 40,
                keywordsKey: "keywords.workspace.theme",
                execute: () => setThemeRef.current("light"),
            },
            {
                id: "workspace.theme-dark",
                group: "workspace",
                labelKey: "workspace.themeDark",
                icon: MoonIcon,
                order: 41,
                keywordsKey: "keywords.workspace.theme",
                execute: () => setThemeRef.current("dark"),
            },
            {
                id: "workspace.theme-system",
                group: "workspace",
                labelKey: "workspace.themeSystem",
                icon: ComputerDesktopIcon,
                order: 42,
                keywordsKey: "keywords.workspace.theme",
                execute: () => setThemeRef.current("system"),
            },
            {
                id: "workspace.language-en",
                group: "workspace",
                labelKey: "workspace.languageEnglish",
                icon: LanguageIcon,
                order: 50,
                keywordsKey: "keywords.workspace.language",
                execute: async (_context, helpers) => {
                    await persistAuthenticatedLocale("en");
                    helpers.router.refresh();
                },
            },
            {
                id: "workspace.language-ja",
                group: "workspace",
                labelKey: "workspace.languageJapanese",
                icon: GlobeAltIcon,
                order: 51,
                keywordsKey: "keywords.workspace.language",
                execute: async (_context, helpers) => {
                    await persistAuthenticatedLocale("ja");
                    helpers.router.refresh();
                },
            },
        ],
        [],
    );

    useRegisterActions(actions);
    return null;
}
