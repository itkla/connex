import { ChevronDownIcon } from "@heroicons/react/20/solid";
import { SAMPLE_WORKSPACE, type LandingTranslation, type SampleSource } from "./sampleWorkspace";

/** Native disclosure keeps sample citations usable before hydration and without JavaScript. */
export function SampleEvidence({ source, t }: { source: SampleSource; t: LandingTranslation }) {
    return (
        <details className="group/source border-t border-border py-3 text-sm" data-sample-source={source}>
            <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-4 rounded-sm font-medium text-foreground outline-none focus-visible:ring-2 focus-visible:ring-brand [&::-webkit-details-marker]:hidden">
                <span>{t(`source_${source}_title`)}</span>
                <ChevronDownIcon aria-hidden="true" className="size-4 shrink-0 group-open/source:rotate-180" />
            </summary>
            <div className="pb-2 pt-1 leading-relaxed text-muted-foreground">
                <time dateTime={SAMPLE_WORKSPACE.sources[source].date}>{t(`source_${source}_date`)}</time>
                <p className="mt-2">{t(`source_${source}_body`)}</p>
            </div>
        </details>
    );
}
