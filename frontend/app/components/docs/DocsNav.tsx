"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ChevronDownIcon } from "@heroicons/react/24/outline";
import { docsCategories, type DocCategory } from "@/app/lib/docs/registry";

function isCategoryActive(pathname: string, slug: string): boolean {
    return pathname === `/docs/${slug}` || pathname.startsWith(`/docs/${slug}/`);
}

function DocsNavGroup({
    category,
    pathname,
    onNavigate,
}: {
    category: DocCategory;
    pathname: string;
    onNavigate?: () => void;
}) {
    const t = useTranslations();
    const categoryActive = isCategoryActive(pathname, category.slug);
    const [open, setOpen] = useState(categoryActive);
    const CategoryIcon = category.icon;
    const groupId = `docs-nav-${category.slug}`;

    return (
        <div>
            <div className="flex items-center gap-1">
                <Link
                    href={`/docs/${category.slug}`}
                    onClick={onNavigate}
                    className={`flex min-w-0 flex-1 items-center gap-2 rounded-md px-2 py-1.5 text-sm font-medium transition-colors ${
                        pathname === `/docs/${category.slug}`
                            ? "text-brand-dark"
                            : "text-foreground/85 hover:text-foreground"
                    }`}
                >
                    <CategoryIcon className="size-4 shrink-0 text-muted-foreground" />
                    <span className="truncate">{t(`${category.namespace}.title`)}</span>
                </Link>
                <button
                    type="button"
                    onClick={() => setOpen((value) => !value)}
                    aria-expanded={open}
                    aria-controls={groupId}
                    aria-label={t(`${category.namespace}.title`)}
                    className="rounded-md p-1 text-muted-foreground transition-colors hover:text-foreground"
                >
                    <ChevronDownIcon className={`size-3.5 transition-transform ${open ? "" : "-rotate-90"}`} />
                </button>
            </div>
            {open ? (
                <ul id={groupId} className="mt-0.5 ml-3 space-y-0.5 border-l border-border pl-3">
                    {category.articles.map((article) => {
                        const href = `/docs/${category.slug}/${article.slug}`;
                        const active = pathname === href;
                        return (
                            <li key={article.slug}>
                                <Link
                                    href={href}
                                    onClick={onNavigate}
                                    aria-current={active ? "page" : undefined}
                                    className={`block rounded-md px-2 py-1.5 text-sm transition-colors ${
                                        active
                                            ? "bg-brand-light font-medium text-brand-dark"
                                            : "text-muted-foreground hover:bg-muted hover:text-foreground"
                                    }`}
                                >
                                    {t(`${category.namespace}.articles.${article.slug}.title`)}
                                </Link>
                            </li>
                        );
                    })}
                </ul>
            ) : null}
        </div>
    );
}

/**
 * The docs table of contents: every category and article from the registry,
 * with collapsible groups and app-consistent active styling. `onNavigate` lets
 * the mobile drawer close itself when a link is followed.
 */
export default function DocsNav({ onNavigate }: { onNavigate?: () => void }) {
    const pathname = usePathname() ?? "";
    return (
        <nav className="space-y-1.5">
            {docsCategories.map((category) => (
                <DocsNavGroup
                    key={category.slug}
                    category={category}
                    pathname={pathname}
                    onNavigate={onNavigate}
                />
            ))}
        </nav>
    );
}
