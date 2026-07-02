import Link from "next/link";
import { getTranslations } from "next-intl/server";
import LanguageSwitcher from "./LanguageSwitcher";

/**
 * Shared marketing footer used by the landing page and the public legal pages.
 * Holds the brand mark, primary navigation, legal links, and the language
 * switcher.
 */
export default async function LandingFooter() {
    const t = await getTranslations("CommonHome");

    return (
        <footer className="border-t border-border bg-card">
            <div className="mx-auto max-w-7xl px-6 py-12 lg:px-8">
                <div className="flex flex-col gap-8 sm:flex-row sm:items-start sm:justify-between">
                    <div className="max-w-xs">
                        <Link href="/" className="flex items-center gap-2.5">
                            <span className="size-3 rounded-[5px] bg-brand" aria-hidden="true" />
                            <span className="text-lg font-bold tracking-tight text-foreground">{t("brand")}</span>
                        </Link>
                        <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{t("footerTagline")}</p>
                    </div>

                    <div className="flex flex-col gap-6 sm:flex-row sm:items-start sm:gap-12">
                        <nav className="flex flex-col gap-3 text-sm">
                            <Link href="/#features" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navFeatures")}
                            </Link>
                            <Link href="/#workflow" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navWorkflow")}
                            </Link>
                            <Link href="/auth/login" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navLogin")}
                            </Link>
                        </nav>
                        <nav className="flex flex-col gap-3 text-sm">
                            <Link href="/privacy" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navPrivacy")}
                            </Link>
                            <Link href="/disclosure" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navDisclosure")}
                            </Link>
                        </nav>
                        <LanguageSwitcher align="start" />
                    </div>
                </div>

                <div className="mt-10 border-t border-border pt-6 text-sm text-muted-foreground">{t("footerRights")}</div>
            </div>
        </footer>
    );
}
