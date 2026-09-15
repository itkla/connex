"use client";

import Link from "next/link";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { Bars3Icon, XMarkIcon, SunIcon, MoonIcon } from "@heroicons/react/24/outline";
import { useTheme } from "next-themes";
import { Button } from "@/components/ui/button";
import LanguageSwitcher from "./LanguageSwitcher";
import styles from "./landing.module.css";

const LAUNCH_SIGNUP_HREF = "/#launch-signup";

function ThemeToggle() {
    const t = useTranslations("CommonHome");
    const { resolvedTheme, setTheme } = useTheme();
    const next = resolvedTheme === "dark" ? "light" : "dark";

    return (
        <button
            type="button"
            onClick={() => setTheme(next)}
            aria-label={t("toggleLightDarkMode")}
            className="inline-flex size-9 items-center justify-center rounded-full border border-border text-foreground transition-transform duration-(--motion-micro) active:scale-[0.95] motion-reduce:active:scale-100"
        >
            <MoonIcon className="size-5 dark:hidden" />
            <SunIcon className="hidden size-5 dark:block" />
        </button>
    );
}

/**
 * Public marketing header, shared by the landing page, the legal pages, and the root 404.
 *
 * Carries its own mobile guarantees — the 44px touch-target floor, the wrap backstop, and the
 * container query that decides the desktop/mobile split — so a page that renders it without the
 * landing page's own stylesheet still gets them.
 *
 * `preLaunch` drops every account action: before launch the only call to action is the launch
 * signup form on the landing page, which the compact header pill anchors to.
 */
export default function LandingNav({ ctaHref, ctaLabel, preLaunch = false }: { ctaHref: string; ctaLabel: string; preLaunch?: boolean }) {
    const t = useTranslations("CommonHome");
    const [open, setOpen] = useState(false);
    const compactCta = preLaunch ? { href: LAUNCH_SIGNUP_HREF, label: t("prelaunch.navCta") } : { href: ctaHref, label: ctaLabel };

    const links = [
        { href: "/#product", label: t("navProduct"), route: false },
        { href: "/#features", label: t("navFeatures"), route: false },
        { href: "/#pricing", label: t("navPricing"), route: false },
        { href: "/#faq", label: t("navFaq"), route: false },
        ...(preLaunch ? [] : [{ href: "/docs", label: t("navDocs"), route: true }]),
    ];

    return (
        <header className={`${styles.nav} sticky top-0 z-40 border-b border-border bg-background/80 backdrop-blur-md`}>
            <nav className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6 lg:px-8">
                <div className="flex items-center gap-8">
                    <Link href="/" className="flex shrink-0 items-center gap-2.5">
                        <span className="size-3 shrink-0 rounded-[5px] bg-brand" aria-hidden="true" />
                        <span className="whitespace-nowrap text-lg font-bold tracking-tight text-foreground max-[359px]:sr-only">{t("brand")}</span>
                    </Link>

                    <div data-landing-desktop className="hidden items-center gap-7 md:flex">
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

                <div data-landing-desktop className="hidden items-center gap-3 md:flex">
                    <ThemeToggle />
                    <LanguageSwitcher />
                    {!preLaunch && <><Link
                        href="/auth/login"
                        className="text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
                    >
                        {t("navLogin")}
                    </Link>
                    <Link
                        href={ctaHref}
                        className="rounded-full bg-brand px-4 py-2 text-sm font-semibold text-brand-foreground transition-[transform,background-color] duration-(--motion-micro) ease-out hover:bg-brand-hover active:scale-[0.97] motion-reduce:active:scale-100"
                    >
                        {ctaLabel}
                    </Link></>}
                </div>

                <div data-landing-mobile className="flex min-w-0 items-center gap-2 md:hidden">
                    <div className="hidden sm:flex">
                        <ThemeToggle />
                    </div>
                    <LanguageSwitcher />
                    <Button asChild variant="brand" size="page" className="min-h-11 shrink-0 whitespace-nowrap px-4">
                        <Link href={compactCta.href} onClick={() => setOpen(false)}>{compactCta.label}</Link>
                    </Button>
                    <button
                        type="button"
                        onClick={() => setOpen((o) => !o)}
                        aria-expanded={open}
                        aria-label={t("navMenu")}
                        className="inline-flex size-9 items-center justify-center rounded-full border border-border text-foreground transition-transform duration-(--motion-micro) active:scale-[0.95] motion-reduce:active:scale-100"
                    >
                        {open ? <XMarkIcon className="size-5" /> : <Bars3Icon className="size-5" />}
                    </button>
                </div>
            </nav>

            {open && (
                <div data-landing-menu className="border-t border-border bg-background px-6 py-4 duration-(--motion-micro) animate-in fade-in-0 slide-in-from-top-2 motion-reduce:animate-none! md:hidden">
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
                        <div className="mt-2 flex sm:hidden">
                            <ThemeToggle />
                        </div>
                        {!preLaunch && <><Link
                            href="/auth/login"
                            onClick={() => setOpen(false)}
                            className="rounded-lg px-2 py-2.5 text-base font-medium text-foreground transition-colors hover:bg-muted"
                        >
                            {t("navLogin")}
                        </Link>
                        <Link
                            href={ctaHref}
                            onClick={() => setOpen(false)}
                            className="mt-2 rounded-full bg-brand px-4 py-3 text-center text-base font-semibold text-brand-foreground transition-transform duration-(--motion-micro) active:scale-[0.98] motion-reduce:active:scale-100"
                        >
                            {ctaLabel}
                        </Link></>}
                    </div>
                </div>
            )}
        </header>
    );
}
