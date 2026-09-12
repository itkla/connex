import Link from "next/link";
import { getTranslations } from "next-intl/server";
import LanguageSwitcher from "./LanguageSwitcher";

/**
 * Shared marketing footer used by the landing page and the public legal pages.
 * Holds the brand mark, primary navigation, legal links, and the language
 * switcher.
 */
export default async function LandingFooter({ withDividers = true, showLogin = true }: { withDividers?: boolean; showLogin?: boolean }) {
    const t = await getTranslations("CommonHome");

    return (
        <footer className={`bg-card ${withDividers ? "border-t border-border" : ""}`}>
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
                        <nav aria-label={t("footerNavProduct")} className="flex flex-col gap-3 text-sm">
                            <Link href="/#product" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navProduct")}
                            </Link>
                            <Link href="/#workflow" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navWorkflow")}
                            </Link>
                            <Link href="/docs" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navDocs")}
                            </Link>
                            {showLogin && <Link href="/auth/login" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navLogin")}
                            </Link>}
                        </nav>
                        <nav aria-label={t("footerNavLegal")} className="flex flex-col gap-3 text-sm">
                            <Link href="/privacy" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navPrivacy")}
                            </Link>
                            <Link href="/disclosure" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navDisclosure")}
                            </Link>
                            <Link href="/legal" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navTerms")}
                            </Link>
                            <Link href="/tokushoho" className="text-muted-foreground transition-colors hover:text-foreground">
                                {t("navTokushoho")}
                            </Link>
                        </nav>
                        <LanguageSwitcher align="start" />
                    </div>
                </div>

                <div className={`text-sm text-muted-foreground ${withDividers ? "mt-10 border-t border-border pt-6" : "mt-8"}`}>{t("footerRights")}</div>
            </div>
        </footer>
    );
}
