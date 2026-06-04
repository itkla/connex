"use client";

import Link from "next/link";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { Bars3Icon, XMarkIcon } from "@heroicons/react/24/outline";
import LanguageSwitcher from "./LanguageSwitcher";

export default function LandingNav({ ctaHref, ctaLabel }: { ctaHref: string; ctaLabel: string }) {
    const t = useTranslations("CommonHome");
    const [open, setOpen] = useState(false);

    const links = [
        { href: "#features", label: t("navFeatures") },
        { href: "#workflow", label: t("navWorkflow") },
    ];

    return (
        // TODO: make this a floating navbar
        <header className="sticky top-0 z-40 border-b border-black/[0.06] bg-white/80 backdrop-blur-md">
            <nav className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6 lg:px-8">
                <div className="flex items-center gap-8">
                    <Link href="/" className="flex items-center gap-2.5">
                        <span className="size-3 rounded-[5px] bg-brand" aria-hidden="true" />
                        <span className="text-lg font-bold tracking-tight text-neutral-900">{t("brand")}</span>
                    </Link>

                    <div className="hidden items-center gap-7 md:flex">
                        {links.map((link) => (
                            <a
                                key={link.href}
                                href={link.href}
                                className="text-sm font-medium text-neutral-600 transition-colors hover:text-neutral-900"
                            >
                                {link.label}
                            </a>
                        ))}
                    </div>
                </div>

                <div className="hidden items-center gap-3 md:flex">
                    <LanguageSwitcher />
                    <Link
                        href="/auth/login"
                        className="text-sm font-medium text-neutral-600 transition-colors hover:text-neutral-900"
                    >
                        {t("navLogin")}
                    </Link>
                    <Link
                        href={ctaHref}
                        className="rounded-full bg-brand px-4 py-2 text-sm font-semibold text-neutral-950 transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.97]"
                    >
                        {ctaLabel}
                    </Link>
                </div>

                <div className="flex items-center gap-2 md:hidden">
                    <LanguageSwitcher />
                    <button
                        type="button"
                        onClick={() => setOpen((o) => !o)}
                        aria-expanded={open}
                        aria-label={t("navMenu")}
                        className="inline-flex size-9 items-center justify-center rounded-full border border-black/10 text-neutral-700 transition active:scale-[0.95]"
                    >
                        {open ? <XMarkIcon className="size-5" /> : <Bars3Icon className="size-5" />}
                    </button>
                </div>
            </nav>

            {open && (
                <div className="border-t border-black/[0.06] bg-white px-6 py-4 duration-200 animate-in fade-in-0 slide-in-from-top-2 md:hidden">
                    <div className="flex flex-col gap-1">
                        {links.map((link) => (
                            <a
                                key={link.href}
                                href={link.href}
                                onClick={() => setOpen(false)}
                                className="rounded-lg px-2 py-2.5 text-base font-medium text-neutral-700 transition-colors hover:bg-neutral-50"
                            >
                                {link.label}
                            </a>
                        ))}
                        <Link
                            href="/auth/login"
                            onClick={() => setOpen(false)}
                            className="rounded-lg px-2 py-2.5 text-base font-medium text-neutral-700 transition-colors hover:bg-neutral-50"
                        >
                            {t("navLogin")}
                        </Link>
                        <Link
                            href={ctaHref}
                            onClick={() => setOpen(false)}
                            className="mt-2 rounded-full bg-brand px-4 py-3 text-center text-base font-semibold text-neutral-950 transition active:scale-[0.98]"
                        >
                            {ctaLabel}
                        </Link>
                    </div>
                </div>
            )}
        </header>
    );
}