import type { Metadata } from "next";
import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { docsCategories } from "@/app/lib/docs/registry";
import { readHomeBlocks } from "@/app/lib/docs/read";
import DocBlocks from "@/app/components/docs/DocBlocks";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("DocsMeta");
    return { title: t("metaTitle"), description: t("metaDescription") };
}

export default async function DocsHomePage() {
    const meta = await getTranslations("DocsMeta");
    const home = await getTranslations("DocsHome");
    const t = await getTranslations();
    const blocks = await readHomeBlocks();

    return (
        <div className="space-y-12">
            <header className="max-w-3xl">
                <p className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {meta("sectionLabel")}
                </p>
                <h1 className="mt-3 font-display text-4xl tracking-tight text-foreground sm:text-5xl">
                    {home("title")}
                </h1>
                <p className="mt-5 text-lg leading-relaxed text-muted-foreground">{home("lead")}</p>
            </header>

            {blocks.length > 0 ? <DocBlocks blocks={blocks} /> : null}

            <section>
                <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {meta("categoriesHeading")}
                </h2>
                <div className="mt-5 grid gap-4 sm:grid-cols-2">
                    {docsCategories.map((category) => {
                        const Icon = category.icon;
                        return (
                            <Link
                                key={category.slug}
                                href={`/docs/${category.slug}`}
                                className="group rounded-2xl border border-border bg-card p-6 transition-colors hover:border-brand/40 hover:bg-muted/40"
                            >
                                <div className="flex size-10 items-center justify-center rounded-xl bg-brand-light">
                                    <Icon className="size-5 text-brand-dark" />
                                </div>
                                <h3 className="mt-4 text-base font-semibold text-foreground">
                                    {t(`${category.namespace}.title`)}
                                </h3>
                                <p className="mt-1.5 text-sm leading-6 text-muted-foreground">
                                    {t(`${category.namespace}.description`)}
                                </p>
                            </Link>
                        );
                    })}
                </div>
            </section>
        </div>
    );
}
