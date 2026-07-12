"use client";

import Link from "next/link";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { Bars3Icon, XMarkIcon, SunIcon, MoonIcon } from "@heroicons/react/24/outline";
import { useTheme } from "next-themes";
import LanguageSwitcher from "./LanguageSwitcher";

function ThemeToggle() {
    const t = useTranslations("CommonHome");
    const { resolvedTheme, setTheme } = useTheme();
    const next = resolvedTheme === "dark" ? "light" : "dark";

    return (
        <button
            type="button"
            onClick={() => setTheme(next)}
            aria-label={t("toggleLightDarkMode", { mode: next })}
            className="inline-flex size-9 items-center justify-center rounded-full border border-border text-foreground transition active:scale-[0.95]"
        >
            <MoonIcon className="size-5 dark:hidden" />
            <SunIcon className="hidden size-5 dark:block" />
        </button>
    );
}

export default function LandingNav({ ctaHref, ctaLabel }: { ctaHref: string; ctaLabel: string }) {
    const t = useTranslations("CommonHome");
    const [open, setOpen] = useState(false);

    const links = [
        { href: "#features", label: t("navFeatures"), route: false },
        { href: "#workflow", label: t("navWorkflow"), route: false },
        { href: "/docs", label: t("navDocs"), route: true },
    ];

    return (
        // TODO: make this a floating navbar
        <header className="sticky top-0 z-40 border-b border-border bg-background/80 backdrop-blur-md">
            <nav className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6 lg:px-8">
                <div className="flex items-center gap-8">
                    <Link href="/" className="flex items-center gap-2.5">
                        <span className="size-3 rounded-[5px] bg-brand" aria-hidden="true" />
                        <span className="text-lg font-bold tracking-tight text-foreground">{t("brand")}</span>
                    </Link>

                    <div className="hidden items-center gap-7 md:flex">
                        {links.map((link) =>
                            link.route ? (
                                <Link
                                    key={link.href}
                                    href={link.href}
                                    className="text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
                                >
                                    {link.label}
                                </Link>
                            ) : (
                                <a
                                    key={link.href}
                                    href={link.href}
                                    className="text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
                                >
                                    {link.label}
                                </a>
                            ),
                        )}
                    </div>
                </div>

                <div className="hidden items-center gap-3 md:flex">
                    <ThemeToggle />
                    <LanguageSwitcher />
                    <Link
                        href="/auth/login"
                        className="text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
                    >
                        {t("navLogin")}
                    </Link>
                    <Link
                        href={ctaHref}
                        className="rounded-full bg-brand px-4 py-2 text-sm font-semibold text-brand-foreground transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.97]"
                    >
                        {ctaLabel}
                    </Link>
                </div>

                <div className="flex items-center gap-2 md:hidden">
                    <ThemeToggle />
                    <LanguageSwitcher />
                    <button
                        type="button"
                        onClick={() => setOpen((o) => !o)}
                        aria-expanded={open}
                        aria-label={t("navMenu")}
                        className="inline-flex size-9 items-center justify-center rounded-full border border-border text-foreground transition active:scale-[0.95]"
                    >
                        {open ? <XMarkIcon className="size-5" /> : <Bars3Icon className="size-5" />}
                    </button>
                </div>
            </nav>

            {open && (
                <div className="border-t border-border bg-background px-6 py-4 duration-200 animate-in fade-in-0 slide-in-from-top-2 md:hidden">
                    <div className="flex flex-col gap-1">
                        {links.map((link) =>
                            link.route ? (
                                <Link
                                    key={link.href}
                                    href={link.href}
                                    onClick={() => setOpen(false)}
                                    className="rounded-lg px-2 py-2.5 text-base font-medium text-foreground transition-colors hover:bg-muted"
                                >
                                    {link.label}
                                </Link>
                            ) : (
                                <a
                                    key={link.href}
                                    href={link.href}
                                    onClick={() => setOpen(false)}
                                    className="rounded-lg px-2 py-2.5 text-base font-medium text-foreground transition-colors hover:bg-muted"
                                >
                                    {link.label}
                                </a>
                            ),
                        )}
                        <Link
                            href="/auth/login"
                            onClick={() => setOpen(false)}
                            className="rounded-lg px-2 py-2.5 text-base font-medium text-foreground transition-colors hover:bg-muted"
                        >
                            {t("navLogin")}
                        </Link>
                        <Link
                            href={ctaHref}
                            onClick={() => setOpen(false)}
                            className="mt-2 rounded-full bg-brand px-4 py-3 text-center text-base font-semibold text-brand-foreground transition active:scale-[0.98]"
                        >
                            {ctaLabel}
                        </Link>
                    </div>
                </div>
            )}
        </header>
    );
}
