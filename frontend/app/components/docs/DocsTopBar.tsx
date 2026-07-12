"use client";

import Link from "next/link";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { useTheme } from "next-themes";
import { Bars3Icon, MoonIcon, SunIcon } from "@heroicons/react/24/outline";
import { Drawer, DrawerContent, DrawerTitle, DrawerTrigger } from "@/components/ui/drawer";
import LanguageSwitcher from "@/app/components/landing/LanguageSwitcher";
import DocsNav from "./DocsNav";

function ThemeToggle() {
    const t = useTranslations("CommonHome");
    const { resolvedTheme, setTheme } = useTheme();
    const next = resolvedTheme === "dark" ? "light" : "dark";

    return (
        <button
            type="button"
            onClick={() => setTheme(next)}
            aria-label={t("toggleLightDarkMode", { mode: next })}
            className="inline-flex size-9 items-center justify-center rounded-full border border-border text-foreground outline-none transition active:scale-[0.95] focus-visible:ring-2 focus-visible:ring-brand"
        >
            <MoonIcon className="size-5 dark:hidden" />
            <SunIcon className="hidden size-5 dark:block" />
        </button>
    );
}

/**
 * Sticky docs header. Adapts its call to action to the visitor's session
 * (`Open app` when signed in, `Sign in` / `Get started` otherwise) and holds the
 * mobile navigation drawer.
 */
export default function DocsTopBar({ authed }: { authed: boolean }) {
    const home = useTranslations("CommonHome");
    const t = useTranslations("DocsMeta");
    const [navOpen, setNavOpen] = useState(false);

    return (
        <header className="sticky top-0 z-40 border-b border-border bg-background/80 backdrop-blur-md">
            <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6 lg:px-8">
                <div className="flex items-center gap-3">
                    <Drawer open={navOpen} onOpenChange={setNavOpen} swipeDirection="left">
                        <DrawerTrigger
                            render={
                                <button
                                    type="button"
                                    aria-label={t("browseDocs")}
                                    className="inline-flex size-9 items-center justify-center rounded-full border border-border text-foreground outline-none transition active:scale-[0.95] focus-visible:ring-2 focus-visible:ring-brand lg:hidden"
                                />
                            }
                        >
                            <Bars3Icon className="size-5" />
                        </DrawerTrigger>
                        <DrawerContent aria-describedby={undefined} className="w-80 p-0">
                            <DrawerTitle className="px-6 pt-6 text-sm font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                {t("browseDocs")}
                            </DrawerTitle>
                            <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-4 pb-8">
                                <DocsNav onNavigate={() => setNavOpen(false)} />
                            </div>
                        </DrawerContent>
                    </Drawer>

                    <Link href="/" className="flex items-center gap-2.5">
                        <span className="size-3 rounded-[5px] bg-brand" aria-hidden="true" />
                        <span className="text-lg font-bold tracking-tight text-foreground">
                            {home("brand")}
                        </span>
                    </Link>
                    <Link
                        href="/docs"
                        className="rounded-full border border-border px-2.5 py-0.5 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
                    >
                        {t("sectionLabel")}
                    </Link>
                </div>

                <div className="flex items-center gap-2 sm:gap-3">
                    <ThemeToggle />
                    <div className="hidden sm:block">
                        <LanguageSwitcher />
                    </div>
                    {authed ? (
                        <Link
                            href="/dashboard"
                            className="rounded-full bg-brand px-4 py-2 text-sm font-semibold text-neutral-950 transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.97]"
                        >
                            {t("openApp")}
                        </Link>
                    ) : (
                        <>
                            <Link
                                href="/auth/login"
                                className="hidden text-sm font-medium text-muted-foreground transition-colors hover:text-foreground sm:inline"
                            >
                                {t("signIn")}
                            </Link>
                            <Link
                                href="/auth/register"
                                className="rounded-full bg-brand px-4 py-2 text-sm font-semibold text-neutral-950 transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.97]"
                            >
                                {t("getStarted")}
                            </Link>
                        </>
                    )}
                </div>
            </div>
        </header>
    );
}
