"use client";

import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { DropdownMenu } from "radix-ui";
import { CheckIcon, ChevronDownIcon, GlobeAltIcon } from "@heroicons/react/16/solid";
import { setLocaleCookie } from "@/app/lib/utils";
import type { Locale } from "@/i18n/config";

const LANGUAGES = [
    { code: "en", labelKey: "languageEnglish" },
    { code: "ja", labelKey: "languageJapanese" },
] as const;

type Align = "start" | "center" | "end";

export default function LanguageSwitcher({ align = "end" }: { align?: Align }) {
    const t = useTranslations("CommonHome");
    const locale = useLocale();
    const router = useRouter();
    const [isPending, startTransition] = useTransition();

    const active = LANGUAGES.find((l) => l.code === locale) ?? LANGUAGES[0];

    function selectLanguage(code: Locale) {
        if (code === locale) return;
        setLocaleCookie(code);
        startTransition(() => router.refresh());
    }

    return (
        <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
                <button
                    type="button"
                    aria-label={t("languageLabel")}
                    data-pending={isPending ? "" : undefined}
                    className="group inline-flex items-center gap-1.5 rounded-full border border-border bg-background/70 px-3 py-1.5 text-sm font-medium text-foreground outline-none transition-[transform,background-color,border-color] duration-150 ease-out hover:border-border hover:bg-background focus-visible:ring-2 focus-visible:ring-brand active:scale-[0.97] data-[state=open]:border-border data-[state=open]:bg-background data-[pending]:opacity-60"
                >
                    <GlobeAltIcon className="size-4 text-muted-foreground transition-colors group-hover:text-foreground" />
                    <span>{t(active.labelKey)}</span>
                    <ChevronDownIcon className="size-3.5 text-muted-foreground transition-transform duration-200 ease-out group-data-[state=open]:rotate-180" />
                </button>
            </DropdownMenu.Trigger>

            <DropdownMenu.Portal>
                <DropdownMenu.Content
                    align={align}
                    sideOffset={8}
                    className="z-50 min-w-[10rem] origin-[var(--radix-dropdown-menu-content-transform-origin)] rounded-xl border border-border bg-popover p-1 text-popover-foreground shadow-lg data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=top]:slide-in-from-bottom-1 data-[side=bottom]:slide-in-from-top-1 motion-reduce:animate-none!"
                >
                    {LANGUAGES.map((lang) => {
                        const isActive = lang.code === locale;
                        return (
                            <DropdownMenu.Item
                                key={lang.code}
                                onSelect={() => selectLanguage(lang.code)}
                                className="flex cursor-pointer items-center justify-between gap-3 rounded-lg px-2.5 py-2 text-sm outline-none transition-colors data-[highlighted]:bg-brand-light data-[highlighted]:text-brand-dark"
                            >
                                <span className={isActive ? "font-medium" : ""}>{t(lang.labelKey)}</span>
                                {isActive && <CheckIcon className="size-4 text-brand-dark" />}
                            </DropdownMenu.Item>
                        );
                    })}
                </DropdownMenu.Content>
            </DropdownMenu.Portal>
        </DropdownMenu.Root>
    );
}
