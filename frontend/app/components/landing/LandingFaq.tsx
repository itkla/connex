"use client";

import { useTranslations } from "next-intl";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";

/**
 * Landing page FAQ. Every answer is checked against what Connex actually ships:
 * the three deployment profiles, the APPI surfaces, the "AI proposes, you apply"
 * contract, CSV import and card scanning, and first-class Japanese.
 */

const QUESTIONS = ["Data", "Appi", "Ai", "Import", "Ja", "Start"] as const;

export default function LandingFaq() {
    const t = useTranslations("CommonHome");

    return (
        <Accordion type="single" collapsible className="mt-12 border-t border-border">
            {QUESTIONS.map((key) => (
                <AccordionItem key={key} value={key} className="border-b border-border">
                    <AccordionTrigger className="py-5 text-left text-lg font-medium text-foreground hover:no-underline">
                        {t(`faq${key}Q`)}
                    </AccordionTrigger>
                    <AccordionContent className="max-w-[68ch] pb-5 text-[15px] leading-relaxed text-muted-foreground [word-break:auto-phrase]">
                        {t(`faq${key}A`)}
                    </AccordionContent>
                </AccordionItem>
            ))}
        </Accordion>
    );
}
