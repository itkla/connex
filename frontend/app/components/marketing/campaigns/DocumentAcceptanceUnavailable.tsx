import { DocumentTextIcon } from "@heroicons/react/24/outline";

/** Copy for a non-diagnostic public document-link failure state. */
export type DocumentAcceptanceUnavailableCopy = {
    title: string;
    body: string;
    footer: string;
};

/** Renders a non-interactive document-link failure without mounting client behavior. */
export default function DocumentAcceptanceUnavailable({
    copy,
}: {
    copy: DocumentAcceptanceUnavailableCopy;
}) {
    return (
        <main className="grid min-h-dvh place-items-center bg-muted/30 px-5 py-12 sm:px-8">
            <section className="w-full max-w-lg rounded-2xl border border-border bg-card p-7 shadow-sm sm:p-10">
                <DocumentTextIcon aria-hidden="true" className="size-10 text-muted-foreground" />
                <h1 className="mt-6 text-balance text-2xl font-semibold tracking-tight text-foreground">
                    {copy.title}
                </h1>
                <p className="mt-3 max-w-prose text-pretty text-sm leading-6 text-muted-foreground">
                    {copy.body}
                </p>
                <p className="mt-10 border-t border-border pt-5 text-xs text-muted-foreground">
                    {copy.footer}
                </p>
            </section>
        </main>
    );
}
