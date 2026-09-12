import Link from "next/link";
import { ChevronDownIcon } from "@heroicons/react/20/solid";
import type { LandingTranslation } from "./sampleWorkspace";

const QUESTIONS = [
    { key: "Import", href: "/docs/tutorials/import-your-contacts" },
    { key: "Ja", href: "/docs" },
    { key: "Ai", href: "/docs/relationship-intelligence/ai-insights" },
    { key: "Data", href: "/privacy" },
] as const;

/** Native disclosures keep practical answers and their documentation reachable without JS. */
export default function LandingFaq({ t }: { t: LandingTranslation }) {
    return (
        <div className="border-t border-border">
            {QUESTIONS.map(({ key, href }) => (
                <details key={key} className="group border-b border-border">
                    <summary className="flex cursor-pointer list-none items-center justify-between gap-5 rounded-sm py-5 text-lg font-medium outline-none focus-visible:ring-2 focus-visible:ring-brand [&::-webkit-details-marker]:hidden">
                        {t(`faq${key}Q`)}<ChevronDownIcon aria-hidden="true" className="size-5 shrink-0 group-open:rotate-180" />
                    </summary>
                    <div className="max-w-[68ch] pb-6 text-base leading-relaxed text-muted-foreground">
                        <p>{t(`faq${key}A`)}</p>
                        <Link href={href} className="mt-3 inline-block min-h-11 py-2 text-foreground underline underline-offset-4">{t("faqReadMore")}</Link>
                    </div>
                </details>
            ))}
        </div>
    );
}
