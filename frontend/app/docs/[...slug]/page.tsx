import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { ArrowLeftIcon, ArrowRightIcon } from "@heroicons/react/24/outline";
import {
    Breadcrumb,
    BreadcrumbItem,
    BreadcrumbLink,
    BreadcrumbList,
    BreadcrumbPage,
    BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import {
    articleNeighbors,
    docsCategories,
    getArticle,
    getCategory,
    type ResolvedArticle,
} from "@/app/lib/docs/registry";
import { readArticleBlocks, readCategoryBlocks } from "@/app/lib/docs/read";
import { extractHeadings } from "@/app/lib/docs/headings";
import DocBlocks from "@/app/components/docs/DocBlocks";
import OnThisPage from "@/app/components/docs/OnThisPage";

type DocsSlugParams = { slug?: string[] };

export function generateStaticParams(): DocsSlugParams[] {
    const params: DocsSlugParams[] = [];
    for (const category of docsCategories) {
        params.push({ slug: [category.slug] });
        for (const article of category.articles) {
            params.push({ slug: [category.slug, article.slug] });
        }
    }
    return params;
}

export async function generateMetadata({
    params,
}: {
    params: Promise<DocsSlugParams>;
}): Promise<Metadata> {
    const { slug = [] } = await params;
    const meta = await getTranslations("DocsMeta");
    const t = await getTranslations();

    if (slug.length === 1) {
        const category = getCategory(slug[0]);
        if (category) {
            return {
                title: `${t(`${category.namespace}.title`)} · ${meta("metaTitle")}`,
                description: t(`${category.namespace}.description`),
            };
        }
    }
    if (slug.length === 2) {
        const resolved = getArticle(slug[0], slug[1]);
        if (resolved) {
            const key = `${resolved.category.namespace}.articles.${resolved.article.slug}`;
            return {
                title: `${t(`${key}.title`)} · ${meta("metaTitle")}`,
                description: t(`${key}.description`),
            };
        }
    }
    return { title: meta("metaTitle") };
}

export default async function DocsSlugPage({ params }: { params: Promise<DocsSlugParams> }) {
    const { slug = [] } = await params;

    if (slug.length === 1) {
        return renderCategory(slug[0]);
    }
    if (slug.length === 2) {
        return renderArticle(slug[0], slug[1]);
    }
    notFound();
}

async function renderCategory(categorySlug: string) {
    const category = getCategory(categorySlug);
    if (!category) notFound();

    const meta = await getTranslations("DocsMeta");
    const t = await getTranslations();
    const blocks = await readCategoryBlocks(category);
    const title = t(`${category.namespace}.title`);

    return (
        <div className="space-y-10">
            <Breadcrumb>
                <BreadcrumbList>
                    <BreadcrumbItem>
                        <BreadcrumbLink asChild>
                            <Link href="/docs">{meta("home")}</Link>
                        </BreadcrumbLink>
                    </BreadcrumbItem>
                    <BreadcrumbSeparator />
                    <BreadcrumbItem>
                        <BreadcrumbPage>{title}</BreadcrumbPage>
                    </BreadcrumbItem>
                </BreadcrumbList>
            </Breadcrumb>

            <header className="max-w-3xl">
                <h1 className="font-display text-4xl tracking-tight text-foreground">{title}</h1>
                <p className="mt-4 text-lg leading-relaxed text-muted-foreground">
                    {t(`${category.namespace}.lead`)}
                </p>
            </header>

            {blocks.length > 0 ? (
                <div className="max-w-3xl">
                    <DocBlocks blocks={blocks} />
                </div>
            ) : null}

            <section>
                <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {meta("inThisCategory")}
                </h2>
                <div className="mt-5 grid gap-4 sm:grid-cols-2">
                    {category.articles.map((article) => {
                        const Icon = article.icon;
                        const key = `${category.namespace}.articles.${article.slug}`;
                        return (
                            <Link
                                key={article.slug}
                                href={`/docs/${category.slug}/${article.slug}`}
                                className="group flex gap-4 rounded-2xl border border-border bg-card p-5 transition-colors hover:border-brand/40 hover:bg-muted/40"
                            >
                                <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-brand-light">
                                    <Icon className="size-5 text-brand-dark" />
                                </div>
                                <div className="min-w-0">
                                    <h3 className="text-sm font-semibold text-foreground">
                                        {t(`${key}.title`)}
                                    </h3>
                                    <p className="mt-1 text-sm leading-6 text-muted-foreground">
                                        {t(`${key}.description`)}
                                    </p>
                                </div>
                            </Link>
                        );
                    })}
                </div>
            </section>
        </div>
    );
}

async function renderArticle(categorySlug: string, articleSlug: string) {
    const resolved = getArticle(categorySlug, articleSlug);
    if (!resolved) notFound();

    const { category, article } = resolved;
    const meta = await getTranslations("DocsMeta");
    const t = await getTranslations();
    const blocks = await readArticleBlocks(category, article);
    const headings = extractHeadings(blocks);
    const { previous, next } = articleNeighbors(categorySlug, articleSlug);

    const key = `${category.namespace}.articles.${article.slug}`;
    const categoryTitle = t(`${category.namespace}.title`);

    return (
        <div className="flex gap-12">
            <div className="min-w-0 flex-1">
                <Breadcrumb>
                    <BreadcrumbList>
                        <BreadcrumbItem>
                            <BreadcrumbLink asChild>
                                <Link href="/docs">{meta("home")}</Link>
                            </BreadcrumbLink>
                        </BreadcrumbItem>
                        <BreadcrumbSeparator />
                        <BreadcrumbItem>
                            <BreadcrumbLink asChild>
                                <Link href={`/docs/${category.slug}`}>{categoryTitle}</Link>
                            </BreadcrumbLink>
                        </BreadcrumbItem>
                        <BreadcrumbSeparator />
                        <BreadcrumbItem>
                            <BreadcrumbPage>{t(`${key}.title`)}</BreadcrumbPage>
                        </BreadcrumbItem>
                    </BreadcrumbList>
                </Breadcrumb>

                <header className="mt-6 max-w-3xl">
                    <h1 className="font-display text-4xl tracking-tight text-foreground">
                        {t(`${key}.title`)}
                    </h1>
                    <p className="mt-4 text-lg leading-relaxed text-muted-foreground">
                        {t(`${key}.description`)}
                    </p>
                </header>

                <div className="mt-10 max-w-3xl">
                    {blocks.length > 0 ? (
                        <DocBlocks blocks={blocks} />
                    ) : (
                        <div className="rounded-2xl border border-dashed border-border bg-muted/30 px-6 py-12 text-center">
                            <p className="text-sm font-semibold text-foreground">{meta("emptyTitle")}</p>
                            <p className="mt-1.5 text-sm text-muted-foreground">{meta("emptyBody")}</p>
                        </div>
                    )}
                </div>

                <ArticleNav previous={previous} next={next} previousLabel={meta("previous")} nextLabel={meta("next")} />
            </div>

            {headings.length > 0 ? (
                <aside className="hidden w-52 shrink-0 xl:block">
                    <div className="sticky top-24">
                        <OnThisPage headings={headings} />
                    </div>
                </aside>
            ) : null}
        </div>
    );
}

async function ArticleNav({
    previous,
    next,
    previousLabel,
    nextLabel,
}: {
    previous: ResolvedArticle | null;
    next: ResolvedArticle | null;
    previousLabel: string;
    nextLabel: string;
}) {
    if (!previous && !next) return null;
    const t = await getTranslations();

    function titleOf(entry: ResolvedArticle) {
        return t(`${entry.category.namespace}.articles.${entry.article.slug}.title`);
    }

    return (
        <nav className="mt-14 grid gap-4 border-t border-border pt-8 sm:grid-cols-2">
            {previous ? (
                <Link
                    href={`/docs/${previous.category.slug}/${previous.article.slug}`}
                    className="group flex flex-col gap-1 rounded-2xl border border-border p-5 transition-colors hover:border-brand/40 hover:bg-muted/40"
                >
                    <span className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        <ArrowLeftIcon className="size-3.5 transition-transform group-hover:-translate-x-0.5" />
                        {previousLabel}
                    </span>
                    <span className="text-sm font-semibold text-foreground">{titleOf(previous)}</span>
                </Link>
            ) : (
                <span className="hidden sm:block" />
            )}
            {next ? (
                <Link
                    href={`/docs/${next.category.slug}/${next.article.slug}`}
                    className="group flex flex-col items-end gap-1 rounded-2xl border border-border p-5 text-right transition-colors hover:border-brand/40 hover:bg-muted/40"
                >
                    <span className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {nextLabel}
                        <ArrowRightIcon className="size-3.5 transition-transform group-hover:translate-x-0.5" />
                    </span>
                    <span className="text-sm font-semibold text-foreground">{titleOf(next)}</span>
                </Link>
            ) : null}
        </nav>
    );
}
